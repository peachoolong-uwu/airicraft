package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class DropItemsTaskExecutor implements WorldTaskExecutor {
	private static final int BUSY_SCREEN_TIMEOUT_TICKS = 100;
	private static final String INVENTORY_BUSY = "inventory_busy";
	private static final String INVENTORY_BUSY_SCREEN = "inventory_busy_screen";
	private static final String INVENTORY_SCREEN_DISMISSED = "inventory_screen_dismissed";

	private final Supplier<MinecraftClient> clientSupplier;
	private final BaritoneFacade navigationFacade;
	private final CameraController cameraController;
	private final MovementController movementController = new MovementController();

	private WorldTaskRequest appliedTask;
	private int busyScreenTicks;
	private boolean terminalEventEmitted;
	private UUID targetUuid;
	private Map<PlayerItemDeliveryPolicy.EntityGeneration, Integer> baselineItemEntityCounts = Map.of();
	private final List<PlayerItemDeliveryPolicy.PickupEvidence> pendingPickupEvidence = new ArrayList<>();
	private PlayerItemDeliveryPolicy.State deliveryState;
	private GoalPosition chaseGoal;
	private int chaseGoalRefreshTicks;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public DropItemsTaskExecutor() {
		this(MinecraftClient::getInstance, null, new CameraController());
	}

	public DropItemsTaskExecutor(BaritoneFacade navigationFacade) {
		this(MinecraftClient::getInstance, navigationFacade, new CameraController());
	}

	public DropItemsTaskExecutor(BaritoneFacade navigationFacade, CameraController cameraController) {
		this(MinecraftClient::getInstance, navigationFacade, cameraController);
	}

	DropItemsTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this(clientSupplier, null, new CameraController());
	}

	DropItemsTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade navigationFacade, CameraController cameraController) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.navigationFacade = navigationFacade;
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.DROP_ITEMS) {
			cancelApproach();
			reset();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			cancelApproach();
			reset();
			appliedTask = request;
			DropItemsStepArgs args = ((WorldTaskRequest.DropItems) request.task()).args();
			deliveryState = args.targetPlayer() == null
				? null
				: PlayerItemDeliveryPolicy.initial(args.itemId(), args.quantity());
		}

		if (!itemDropActuationAllowed(sessionSnapshot)) {
			stopApproach(clientSupplier.get());
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.interactionManager == null || player == null || client.world == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		if (dismissCurrentScreenIfSafe(client, player)) {
			busyScreenTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, INVENTORY_SCREEN_DISMISSED);
			return Optional.empty();
		}
		Optional<String> readinessFailure = readinessFailure(client, player);
		if (readinessFailure.isPresent()) {
			if (INVENTORY_BUSY_SCREEN.equals(readinessFailure.get()) && shouldWaitForBusyScreen(busyScreenTicks)) {
				busyScreenTicks++;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, INVENTORY_BUSY);
				return Optional.empty();
			}
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, INVENTORY_BUSY));
		}
		busyScreenTicks = 0;

		DropItemsStepArgs args = ((WorldTaskRequest.DropItems) request.task()).args();
		ScreenHandler handler = player.currentScreenHandler;
		List<DropSlot> matchingSlots = matchingSlots(handler, args.itemId());
		int available = matchingSlots.stream().mapToInt(DropSlot::count).sum();
		if (args.targetPlayer() != null) {
			return tickDelivery(client, player, request, args, available, sessionSnapshot.tickCount());
		}
		if (available < args.quantity()) {
			return fail(request, available == 0
				? TaskFailure.of(TaskFailureCode.MISSING_FACT, "item_not_found")
				: TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_items"));
		}

		for (DropClick click : planDropClicks(matchingSlots, args.quantity())) {
			performDropClick(client, player, handler, click);
		}
		return complete(request);
	}

	private Optional<TaskTerminalEvent> tickDelivery(
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		DropItemsStepArgs args,
		int available,
		long tick
	) {
		Optional<AbstractClientPlayerEntity> target = findTarget(client, args.targetPlayer());
		Optional<PlayerItemDeliveryPolicy.TargetObservation> targetObservation = target.map(this::observeTarget);
		PlayerItemDeliveryPolicy.Observation observation = new PlayerItemDeliveryPolicy.Observation(
			player.getX(),
			player.getY(),
			player.getZ(),
			available,
			targetObservation,
			observeDroppedItems(client, args),
			List.copyOf(pendingPickupEvidence)
		);
		PlayerItemDeliveryPolicy.Decision decision = PlayerItemDeliveryPolicy.decide(deliveryState, observation);
		pendingPickupEvidence.clear();
		if (decision.command() == PlayerItemDeliveryPolicy.Command.DROP && target.isPresent()) {
			cameraController.lookAt(client, targetAimPoint(target.get()));
			if (!cameraController.isLookingAt(client, targetAimPoint(target.get()), 5.0F)) {
				stopApproach(client);
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "aiming_at_recipient");
				return Optional.empty();
			}
		}
		deliveryState = decision.nextState();
		return switch (decision.command()) {
			case APPROACH -> {
				if (target.isEmpty()) {
					yield fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_found"));
				}
				yield approachTarget(client, target.get(), request, decision.reason(), tick);
			}
			case AIM -> {
				stopApproach(client);
				target.ifPresent(value -> cameraController.lookAt(client, targetAimPoint(value)));
				snapshot = snapshot(TaskExecutionState.RUNNING, request, decision.reason());
				yield Optional.empty();
			}
			case DROP -> {
				stopApproach(client);
				target.ifPresent(value -> cameraController.lookAt(client, targetAimPoint(value)));
				baselineItemEntityCounts = itemEntityCounts(client, args.itemId());
				ScreenHandler handler = player.currentScreenHandler;
				for (DropClick click : planDropClicks(matchingSlots(handler, args.itemId()), args.quantity())) {
					performDropClick(client, player, handler, click);
				}
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "dropped_items_waiting_for_delivery");
				yield Optional.empty();
			}
			case WAIT -> {
				target.ifPresent(value -> cameraController.lookAt(client, targetAimPoint(value)));
				snapshot = snapshot(TaskExecutionState.RUNNING, request, decision.reason());
				yield Optional.empty();
			}
			case SUCCEED -> complete(request, decision.reason());
			case FAIL -> fail(request, TaskFailure.of(decision.failureCode(), decision.reason()));
		};
	}

	private Optional<TaskTerminalEvent> approachTarget(
		MinecraftClient client,
		AbstractClientPlayerEntity target,
		WorldTaskRequest request,
		String reason,
		long tick
	) {
		double distance = client.player.distanceTo(target);
		if (distance <= 10.0D && !movementController.snapshot().stuck()) {
			cameraController.lookAt(client, targetAimPoint(target));
			cancelBaritoneChase();
			movementController.moveForward(client, true, false, tick);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, reason + " direct_chase");
			return Optional.empty();
		}
		if (navigationFacade != null && navigationFacade.isLoaded()) {
			movementController.stop(client);
			Optional<String> pathEvent = navigationFacade.pollPathEvent();
			if (pathEvent.map(event -> "CALC_FAILED".equalsIgnoreCase(event.trim())).orElse(false)) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_unreachable"));
			}
			GoalPosition nextGoal = new GoalPosition(target.getBlockPos().getX(), target.getBlockPos().getY(), target.getBlockPos().getZ(), false);
			if (chaseGoal == null || !chaseGoal.equals(nextGoal) || chaseGoalRefreshTicks >= 10) {
				navigationFacade.startNavigateNear(nextGoal, 2);
				chaseGoal = nextGoal;
				chaseGoalRefreshTicks = 0;
			}
			else {
				chaseGoalRefreshTicks++;
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, reason + " baritone_chase");
			return Optional.empty();
		}
		movementController.moveForward(client, true, false, tick);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, reason + " direct_chase");
		return Optional.empty();
	}

	private Optional<AbstractClientPlayerEntity> findTarget(MinecraftClient client, String targetName) {
		for (AbstractClientPlayerEntity candidate : client.world.getPlayers()) {
			if (!candidate.getName().getString().equals(targetName) || !candidate.isAlive()) {
				continue;
			}
			if (targetUuid == null) {
				targetUuid = candidate.getUuid();
			}
			return Optional.of(candidate);
		}
		return Optional.empty();
	}

	private PlayerItemDeliveryPolicy.TargetObservation observeTarget(AbstractClientPlayerEntity target) {
		return new PlayerItemDeliveryPolicy.TargetObservation(
			target.getUuid(),
			target.getName().getString(),
			target.getX(),
			target.getY(),
			target.getZ()
		);
	}

	private List<PlayerItemDeliveryPolicy.DroppedItemEvidence> observeDroppedItems(
		MinecraftClient client,
		DropItemsStepArgs args
	) {
		if (deliveryState == null || deliveryState.phase() != PlayerItemDeliveryPolicy.Phase.AWAIT_DELIVERY) {
			return List.of();
		}
		Box area = client.player.getBoundingBox().expand(64.0D);
		ArrayList<PlayerItemDeliveryPolicy.DroppedItemEvidence> evidence = new ArrayList<>();
		for (ItemEntity itemEntity : client.world.getEntitiesByClass(ItemEntity.class, area, value -> true)) {
			ItemStack stack = itemEntity.getStack();
			String itemId = Registries.ITEM.getId(stack.getItem()).toString();
			if (!args.itemId().equals(itemId)) {
				continue;
			}
			int droppedCount = agentAttributedQuantity(
				new PlayerItemDeliveryPolicy.EntityGeneration(itemEntity.getId(), itemEntity.getUuid()),
				stack.getCount(),
				baselineItemEntityCounts
			);
			if (droppedCount > 0) {
				evidence.add(new PlayerItemDeliveryPolicy.DroppedItemEvidence(
					new PlayerItemDeliveryPolicy.EntityGeneration(itemEntity.getId(), itemEntity.getUuid()),
					itemId,
					droppedCount,
					itemEntity.getX(),
					itemEntity.getY(),
					itemEntity.getZ()
				));
			}
		}
		return List.copyOf(evidence);
	}

	@Override
	public void onPlayerItemPickupObserved(
		int entityId,
		UUID entityUuid,
		String itemId,
		int pickupDelta,
		int agentAttributedQuantity,
		UUID collectorIdentity,
		UUID observationId
	) {
		if (deliveryState == null || deliveryState.phase() != PlayerItemDeliveryPolicy.Phase.AWAIT_DELIVERY
			|| entityUuid == null || itemId == null || itemId.isBlank() || pickupDelta <= 0
			|| agentAttributedQuantity <= 0 || collectorIdentity == null || observationId == null) {
			return;
		}
		PlayerItemDeliveryPolicy.EntityGeneration generation = new PlayerItemDeliveryPolicy.EntityGeneration(entityId, entityUuid);
		int baselineAttributedQuantity = agentAttributedQuantity(generation, agentAttributedQuantity, baselineItemEntityCounts);
		int observedAttributedQuantity = deliveryState.observedEntityCounts().getOrDefault(generation, 0);
		int attributedQuantity = Math.max(baselineAttributedQuantity, observedAttributedQuantity);
		if (attributedQuantity <= 0) {
			return;
		}
		pendingPickupEvidence.add(new PlayerItemDeliveryPolicy.PickupEvidence(
			generation,
			itemId,
			pickupDelta,
			attributedQuantity,
			collectorIdentity,
			observationId
		));
	}

	static int agentAttributedQuantity(PlayerItemDeliveryPolicy.EntityGeneration generation, int observedEntityStackCount,
		Map<PlayerItemDeliveryPolicy.EntityGeneration, Integer> baselineCounts) {
		if (observedEntityStackCount < 0) {
			throw new IllegalArgumentException("observedEntityStackCount must not be negative");
		}
		int baselineCount = baselineCounts == null ? 0 : baselineCounts.getOrDefault(generation, 0);
		return Math.max(0, observedEntityStackCount - baselineCount);
	}

	static int agentAttributedQuantity(int entityId, int observedEntityStackCount, Map<Integer, Integer> baselineCounts) {
		if (observedEntityStackCount < 0) {
			throw new IllegalArgumentException("observedEntityStackCount must not be negative");
		}
		int baselineCount = baselineCounts == null ? 0 : baselineCounts.getOrDefault(entityId, 0);
		return Math.max(0, observedEntityStackCount - baselineCount);
	}

	private static Map<PlayerItemDeliveryPolicy.EntityGeneration, Integer> itemEntityCounts(MinecraftClient client, String itemId) {
		Map<PlayerItemDeliveryPolicy.EntityGeneration, Integer> counts = new HashMap<>();
		Box area = client.player.getBoundingBox().expand(64.0D);
		for (ItemEntity itemEntity : client.world.getEntitiesByClass(ItemEntity.class, area, value -> true)) {
			if (itemId.equals(Registries.ITEM.getId(itemEntity.getStack().getItem()).toString())) {
				counts.put(new PlayerItemDeliveryPolicy.EntityGeneration(itemEntity.getId(), itemEntity.getUuid()), itemEntity.getStack().getCount());
			}
		}
		return Map.copyOf(counts);
	}

	private static Vec3d targetAimPoint(AbstractClientPlayerEntity target) {
		return target.getBoundingBox().getCenter();
	}

	private static Optional<String> readinessFailure(MinecraftClient client, ClientPlayerEntity player) {
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return Optional.of(INVENTORY_BUSY);
		}
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) {
			return Optional.of(INVENTORY_BUSY_SCREEN);
		}
		if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
			return Optional.of(INVENTORY_BUSY);
		}
		return Optional.empty();
	}

	private static boolean dismissCurrentScreenIfSafe(MinecraftClient client, ClientPlayerEntity player) {
		if (!shouldDismissBusyScreen(currentScreenName(client))) {
			return false;
		}
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return false;
		}
		if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
			return false;
		}
		ScreenCloseSafety.clearScreen(client, "drop_items_screen_dismiss");
		return true;
	}

	private static String currentScreenName(MinecraftClient client) {
		return client.currentScreen == null ? null : client.currentScreen.getClass().getSimpleName();
	}

	static boolean shouldDismissBusyScreen(String screenName) {
		return "ChatScreen".equals(screenName) || "GameMenuScreen".equals(screenName);
	}

	static boolean itemDropActuationAllowed(SessionSnapshot sessionSnapshot) {
		return sessionSnapshot != null && sessionSnapshot.companionActuationAllowed();
	}

	static boolean shouldWaitForBusyScreen(int busyScreenTicks) {
		return busyScreenTicks < BUSY_SCREEN_TIMEOUT_TICKS;
	}

	private static List<DropSlot> matchingSlots(ScreenHandler handler, String itemId) {
		ArrayList<DropSlot> slots = new ArrayList<>();
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (stack.isEmpty()) {
				continue;
			}
			String stackItemId = Registries.ITEM.getId(stack.getItem()).toString();
			if (itemId.equals(stackItemId)) {
				slots.add(new DropSlot(slot, stack.getCount()));
			}
		}
		return List.copyOf(slots);
	}

	static List<DropClick> planDropClicks(List<DropSlot> slots, int quantity) {
		ArrayList<DropClick> clicks = new ArrayList<>();
		int remaining = quantity;
		for (DropSlot slot : slots) {
			if (remaining <= 0) {
				break;
			}
			int dropCount = Math.min(slot.count(), remaining);
			if (dropCount > 0) {
				clicks.add(new DropClick(slot.slotId(), dropCount));
				remaining -= dropCount;
			}
		}
		return List.copyOf(clicks);
	}

	private static void performDropClick(MinecraftClient client, ClientPlayerEntity player, ScreenHandler handler, DropClick click) {
		int remaining = click.count();
		while (remaining > 0) {
			ItemStack currentStack = handler.getSlot(click.slotId()).getStack();
			if (!currentStack.isEmpty() && remaining >= currentStack.getCount()) {
				int stackCount = currentStack.getCount();
				client.interactionManager.clickSlot(handler.syncId, click.slotId(), 1, SlotActionType.THROW, player);
				remaining -= stackCount;
			}
			else {
				client.interactionManager.clickSlot(handler.syncId, click.slotId(), 0, SlotActionType.THROW, player);
				remaining--;
			}
		}
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request) {
		return complete(request, "dropped_items");
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String event) {
		cancelApproach();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, event);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		DropItemsStepArgs args = ((WorldTaskRequest.DropItems) request.task()).args();
		String message = args.targetPlayer() == null
			? completionMessage(args)
			: event + " itemId=" + args.itemId()
				+ " quantity=" + args.quantity()
				+ " targetPlayer=" + args.targetPlayer();
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

	private static String completionMessage(DropItemsStepArgs args) {
		return "dropped_items itemId=" + args.itemId()
			+ " quantity=" + args.quantity()
			+ (args.targetPlayer() == null ? "" : " targetPlayer=" + args.targetPlayer());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "ItemDrop", event, null, null);
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
		appliedTask = null;
		busyScreenTicks = 0;
		terminalEventEmitted = false;
		targetUuid = null;
		baselineItemEntityCounts = Map.of();
		pendingPickupEvidence.clear();
		deliveryState = null;
		chaseGoal = null;
		chaseGoalRefreshTicks = 0;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void stopApproach(MinecraftClient client) {
		movementController.stop(client);
		cancelBaritoneChase();
	}

	private void cancelApproach() {
		stopApproach(clientSupplier.get());
	}

	private void cancelBaritoneChase() {
		if (chaseGoal != null && navigationFacade != null && navigationFacade.isLoaded()) {
			navigationFacade.cancel();
		}
		chaseGoal = null;
		chaseGoalRefreshTicks = 0;
	}

	record DropSlot(int slotId, int count) {
	}

	record DropClick(int slotId, int count) {
	}
}
