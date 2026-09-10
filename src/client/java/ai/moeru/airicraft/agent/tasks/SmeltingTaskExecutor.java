package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SmeltingTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;

	private final Supplier<MinecraftClient> clientSupplier;
	private final SmeltingProcessManager processManager;
	private final BaritoneFacade baritoneFacade;
	private final PlacementSneakController placementSneakController = new PlacementSneakController();

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private boolean navigationStarted;
	private boolean openedStationForTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public SmeltingTaskExecutor() {
		this(MinecraftClient::getInstance, new SmeltingProcessManager(), null);
	}

	public SmeltingTaskExecutor(SmeltingProcessManager processManager) {
		this(MinecraftClient::getInstance, processManager, null);
	}

	public SmeltingTaskExecutor(SmeltingProcessManager processManager, BaritoneFacade baritoneFacade) {
		this(MinecraftClient::getInstance, processManager, baritoneFacade);
	}

	SmeltingTaskExecutor(Supplier<MinecraftClient> clientSupplier, SmeltingProcessManager processManager, BaritoneFacade baritoneFacade) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.processManager = Objects.requireNonNull(processManager, "processManager");
		this.baritoneFacade = baritoneFacade;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || !isSmeltingType(activeTask.get().type())) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (sessionSnapshot == null || !sessionSnapshot.companionActuationAllowed()) {
			placementSneakController.release(clientSupplier.get());
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || client.interactionManager == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		return request.type() == WorldTaskType.SMELT_ITEMS
			? tickSmeltItems(request, client, player, sessionSnapshot.tickCount())
			: tickCollectSmeltedItems(request, client, player);
	}

	private Optional<TaskTerminalEvent> tickSmeltItems(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player, long tick) {
		SmeltingOption option = processManager.registeredOption(smeltArgs(request).optionId());
		if (option == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "option_not_found"));
		}
		if (option.stationCandidate().source() == SmeltingStationSource.OPEN_SCREEN
			&& player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler) {
			return insertSmeltingInputs(request, client, player, handler, option, tick);
		}
		BlockPos stationPos = stationPos(option.stationObservation().key());
		if (stationPos == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "station_unavailable"));
		}
		StationReadiness stationReadiness = ensureStationReady(request, client, player, option, stationPos);
		if (stationReadiness.failure() != null) {
			return fail(request, stationReadiness.failure());
		}
		if (!stationReadiness.ready()) {
			return Optional.empty();
		}
		if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "furnace_screen_not_open"));
		}
		if (!handler.getCursorStack().isEmpty()) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "cursor_not_empty"));
		}
		option = processManager.registeredOption(smeltArgs(request).optionId());
		if (option == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "option_not_found"));
		}
		return insertSmeltingInputs(request, client, player, handler, option, tick);
	}

	private Optional<TaskTerminalEvent> insertSmeltingInputs(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		AbstractFurnaceScreenHandler handler,
		SmeltingOption option,
		long tick
	) {
		SmeltItemsStepArgs args = smeltArgs(request);
		FuelSelection fuel = fuelSelection(client, player, handler, option, args).orElse(null);
		if (fuel == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_fuel"));
		}
		if (fuel.quantity() > 0 && !moveItemsToSlot(client, player, handler, fuel.itemId(), 1, fuel.quantity())) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_fuel"));
		}
		if (!moveItemsToSlot(client, player, handler, option.inputItemId(), 0, args.inputQuantity())) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_input"));
		}
		processManager.updateProcessFingerprint(
			option.optionId(),
			option.stationObservation().key(),
			screenSlotSnapshot(handler),
			tick
		);
		return complete(request, "smelting_started");
	}

	private Optional<TaskTerminalEvent> tickCollectSmeltedItems(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player) {
		CollectSmeltedItemsStepArgs args = collectArgs(request);
		String processId = args.processId() == null ? processManager.preferredCollectionProcessId() : args.processId();
		SmeltingStationKey key = processId == null
			? processManager.confirmedCollectionStationKey(args.confirmationToken())
			: processManager.processStationKey(processId);
		BlockPos stationPos = stationPos(key);
		if (stationPos == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "station_unavailable"));
		}
		StationReadiness stationReadiness = ensureExistingStationOpen(request, client, player, stationPos);
		if (stationReadiness.failure() != null) {
			return fail(request, stationReadiness.failure());
		}
		if (!stationReadiness.ready()) {
			return Optional.empty();
		}
		if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "furnace_screen_not_open"));
		}
		SmeltingSlotSnapshot slots = screenSlotSnapshot(handler);
		if (handler.getSlot(2).getStack().isEmpty()
			|| processId != null && !processManager.processOutputReadyForCollection(processId, slots)) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_output");
			return Optional.empty();
		}
		client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
		if (processId != null) {
			processManager.cancel(processId);
		}
		return complete(request, "smelting_collected");
	}

	private StationReadiness ensureStationReady(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player, SmeltingOption option, BlockPos stationPos) {
		if (option.stationCandidate().source() == SmeltingStationSource.PLACE_FROM_INVENTORY && !isFurnaceBlock(client, stationPos)) {
			if (!canPlaceAt(client, stationPos)) {
				Optional<BlockPos> fallback = chooseFurnacePlacement(client, player);
				if (fallback.isPresent()) {
					BlockPos fallbackPos = fallback.get();
					SmeltingStationKey oldKey = option.stationObservation().key();
					SmeltingStationKey newKey = new SmeltingStationKey(oldKey.dimensionId(), fallbackPos.getX(), fallbackPos.getY(), fallbackPos.getZ());
					processManager.relocatePlacementProcess(option.optionId(), oldKey, newKey, player.squaredDistanceTo(Vec3d.ofCenter(fallbackPos)));
					stationPos = fallbackPos;
				}
				else {
					return StationReadiness.failed(TaskFailure.of(TaskFailureCode.UNKNOWN, "furnace_placement_blocked"));
				}
			}
			if (!withinInteractionRange(player, stationPos)) {
				return navigateOrFail(request, stationPos);
			}
			PlacementAttempt placement = placeFurnace(client, player, stationPos);
			if (!placement.placed()) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "placing_furnace:" + placement.reason());
				return StationReadiness.notReady();
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_furnace");
			return StationReadiness.notReady();
		}
		return ensureExistingStationOpen(request, client, player, stationPos);
	}

	private StationReadiness ensureExistingStationOpen(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player, BlockPos stationPos) {
		if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler) {
			if (!openedStationForTask) {
				if (player.currentScreenHandler.getCursorStack().isEmpty()) {
					ScreenCloseSafety.closeHandledScreen(player, "smelting_existing_station_close");
				}
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "closing_existing_furnace_screen");
				return StationReadiness.notReady();
			}
			return StationReadiness.readyState();
		}
		if (!isFurnaceBlock(client, stationPos)) {
			return StationReadiness.failed(TaskFailure.of(TaskFailureCode.UNKNOWN, "station_unavailable"));
		}
		if (!withinInteractionRange(player, stationPos)) {
			return navigateOrFail(request, stationPos);
		}
		BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(stationPos), Direction.UP, stationPos, false);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
		if (result.isAccepted()) {
			player.swingHand(Hand.MAIN_HAND);
			openedStationForTask = true;
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "opening_furnace");
		return StationReadiness.notReady();
	}

	private StationReadiness navigateOrFail(WorldTaskRequest request, BlockPos stationPos) {
		if (baritoneFacade == null || !baritoneFacade.isLoaded()) {
			return StationReadiness.failed(TaskFailure.of(TaskFailureCode.UNKNOWN, "station_out_of_range"));
		}
		if (!navigationStarted) {
			baritoneFacade.startNavigateNear(new GoalPosition(stationPos.getX(), stationPos.getY(), stationPos.getZ(), false), 3);
			navigationStarted = true;
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "navigating_to_furnace");
		return StationReadiness.notReady();
	}

	private Optional<FuelSelection> fuelSelection(
		MinecraftClient client,
		ClientPlayerEntity player,
		ScreenHandler handler,
		SmeltingOption option,
		SmeltItemsStepArgs args
	) {
		int requiredFuelTicks = args.inputQuantity() * option.cookTimeTicks();
		if (args.fuelMode() == SmeltingFuelMode.MANUAL) {
			return manualFuelSelection(client, handler, args, requiredFuelTicks);
		}
		ItemStack existingFuel = handler.getSlot(1).getStack();
		if (existingFuel.isEmpty() && furnaceBurning(handler)) {
			return Optional.of(new FuelSelection(null, 0));
		}
		if (!existingFuel.isEmpty()) {
			if (!client.world.getFuelRegistry().isFuel(existingFuel)) {
				return Optional.empty();
			}
			int needed = fuelItemsNeeded(requiredFuelTicks, client.world.getFuelRegistry().getFuelTicks(existingFuel));
			String existingFuelItemId = itemId(existingFuel);
			if (existingFuel.getCount() >= needed) {
				return Optional.of(new FuelSelection(null, 0));
			}
			if (existingFuel.getCount() + sourceItemCount(handler, existingFuelItemId) >= needed) {
				return Optional.of(new FuelSelection(existingFuelItemId, needed));
			}
			return Optional.empty();
		}
		Map<String, Integer> availableFuelCounts = new LinkedHashMap<>();
		Map<String, Integer> fuelTicksByItemId = new LinkedHashMap<>();
		for (int slot = 3; slot < handler.slots.size(); slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (stack.isEmpty() || !client.world.getFuelRegistry().isFuel(stack)) {
				continue;
			}
			String itemId = itemId(stack);
			availableFuelCounts.merge(itemId, stack.getCount(), Integer::sum);
			fuelTicksByItemId.putIfAbsent(itemId, client.world.getFuelRegistry().getFuelTicks(stack));
		}
		FuelSelection best = null;
		for (Map.Entry<String, Integer> entry : availableFuelCounts.entrySet()) {
			int needed = fuelItemsNeeded(requiredFuelTicks, fuelTicksByItemId.getOrDefault(entry.getKey(), 0));
			if (needed > 0 && entry.getValue() >= needed) {
				if (best == null || needed < best.quantity()) {
					best = new FuelSelection(entry.getKey(), needed);
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static Optional<FuelSelection> manualFuelSelection(
		MinecraftClient client,
		ScreenHandler handler,
		SmeltItemsStepArgs args,
		int requiredFuelTicks
	) {
		if (client == null || client.world == null || args.fuelItemId() == null || args.fuelItemId().isBlank()) {
			return Optional.empty();
		}
		String requestedFuelItemId = args.fuelItemId();
		int matchingFuelCount = 0;
		int fuelTicksPerItem = 0;

		ItemStack existingFuel = handler.getSlot(1).getStack();
		if (!existingFuel.isEmpty()) {
			if (!requestedFuelItemId.equals(itemId(existingFuel)) || !client.world.getFuelRegistry().isFuel(existingFuel)) {
				return Optional.empty();
			}
			matchingFuelCount += existingFuel.getCount();
			fuelTicksPerItem = client.world.getFuelRegistry().getFuelTicks(existingFuel);
		}

		for (int slot = 3; slot < handler.slots.size(); slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (stack.isEmpty() || !requestedFuelItemId.equals(itemId(stack))) {
				continue;
			}
			if (!client.world.getFuelRegistry().isFuel(stack)) {
				return Optional.empty();
			}
			matchingFuelCount += stack.getCount();
			if (fuelTicksPerItem <= 0) {
				fuelTicksPerItem = client.world.getFuelRegistry().getFuelTicks(stack);
			}
		}

		if (!fuelQuantityCoversCookTime(1, requiredFuelTicks, fuelTicksPerItem, args.fuelQuantity())) {
			return Optional.empty();
		}
		return matchingFuelCount >= args.fuelQuantity()
			? Optional.of(new FuelSelection(requestedFuelItemId, args.fuelQuantity()))
			: Optional.empty();
	}

	static int fuelItemsNeeded(int requiredFuelTicks, int fuelTicksPerItem) {
		if (requiredFuelTicks <= 0) {
			return 0;
		}
		if (fuelTicksPerItem <= 0) {
			return Integer.MAX_VALUE;
		}
		return (requiredFuelTicks + fuelTicksPerItem - 1) / fuelTicksPerItem;
	}

	static boolean fuelQuantityCoversCookTime(int inputQuantity, int cookTimeTicks, int fuelTicksPerItem, int fuelQuantity) {
		if (inputQuantity <= 0 || cookTimeTicks <= 0 || fuelQuantity <= 0) {
			return false;
		}
		return fuelItemsNeeded(inputQuantity * cookTimeTicks, fuelTicksPerItem) <= fuelQuantity;
	}

	private static boolean furnaceBurning(ScreenHandler handler) {
		return handler instanceof AbstractFurnaceScreenHandler furnaceHandler && furnaceHandler.isBurning();
	}

	private static boolean moveItemsToSlot(MinecraftClient client, ClientPlayerEntity player, ScreenHandler handler, String itemId, int targetSlot, int quantity) {
		ItemStack targetStack = handler.getSlot(targetSlot).getStack();
		int remaining = remainingItemsToMove(itemId(targetStack), targetStack.getCount(), itemId, quantity);
		if (remaining < 0) {
			return false;
		}
		while (remaining > 0) {
			int sourceSlot = findSourceSlot(handler, itemId);
			if (sourceSlot < 0) {
				return false;
			}
			ItemStack sourceStack = handler.getSlot(sourceSlot).getStack();
			int moved = moveFromSourceToTarget(client, player, handler, sourceSlot, targetSlot, Math.min(remaining, sourceStack.getCount()));
			if (moved <= 0) {
				return false;
			}
			remaining -= moved;
		}
		return true;
	}

	static int remainingItemsToMove(String currentItemId, int currentCount, String desiredItemId, int desiredQuantity) {
		if (desiredQuantity <= 0) {
			return 0;
		}
		if (currentItemId == null || currentCount <= 0) {
			return desiredQuantity;
		}
		if (!currentItemId.equals(desiredItemId)) {
			return -1;
		}
		return Math.max(0, desiredQuantity - currentCount);
	}

	private static int sourceItemCount(ScreenHandler handler, String itemId) {
		int count = 0;
		for (int slot = 3; slot < handler.slots.size(); slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static int moveFromSourceToTarget(
		MinecraftClient client,
		ClientPlayerEntity player,
		ScreenHandler handler,
		int sourceSlot,
		int targetSlot,
		int maxQuantity
	) {
		if (!handler.getCursorStack().isEmpty()) {
			return 0;
		}
		ItemStack targetStack = handler.getSlot(targetSlot).getStack();
		ItemStack sourceStack = handler.getSlot(sourceSlot).getStack();
		int targetCountBefore = targetStack.isEmpty() ? 0 : targetStack.getCount();
		if (sourceStack.isEmpty()) {
			return 0;
		}
		if (!targetStack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(targetStack, sourceStack)) {
			return 0;
		}
		int targetSpace = targetStack.isEmpty()
			? Math.min(sourceStack.getMaxCount(), 64)
			: Math.max(0, targetStack.getMaxCount() - targetStack.getCount());
		int toMove = Math.min(maxQuantity, targetSpace);
		if (toMove <= 0) {
			return 0;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
		for (int index = 0; index < toMove; index++) {
			client.interactionManager.clickSlot(handler.syncId, targetSlot, 1, SlotActionType.PICKUP, player);
		}
		if (!handler.getCursorStack().isEmpty()) {
			client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
		}
		ItemStack targetAfter = handler.getSlot(targetSlot).getStack();
		int targetCountAfter = targetAfter.isEmpty() ? 0 : targetAfter.getCount();
		return Math.max(0, targetCountAfter - targetCountBefore);
	}

	private static int findSourceSlot(ScreenHandler handler, String itemId) {
		for (int slot = 3; slot < handler.slots.size(); slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean isFurnaceBlock(MinecraftClient client, BlockPos pos) {
		if (client == null || client.world == null || pos == null || !client.world.isChunkLoaded(pos)) {
			return false;
		}
		BlockState state = client.world.getBlockState(pos);
		return state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER);
	}

	private PlacementAttempt placeFurnace(MinecraftClient client, ClientPlayerEntity player, BlockPos pos) {
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return new PlacementAttempt(false, "inventory_not_ready");
		}
		Hand hand = selectFurnacePlacementHand(client, player);
		if (hand == null) {
			return new PlacementAttempt(false, "furnace_not_selectable");
		}
		PlacementSneakController.Preparation sneakPreparation = placementSneakController.prepare(client, player);
		if (sneakPreparation != PlacementSneakController.Preparation.READY) {
			return new PlacementAttempt(false, switch (sneakPreparation) {
				case PRESS_AND_WAIT -> "preparing_sneak";
				case WAITING -> "waiting_for_sneak";
				case READY -> throw new IllegalStateException("ready placement handled above");
			});
		}
		BlockPos support = pos.down();
		BlockHitResult hitResult = new BlockHitResult(
			new Vec3d(support.getX() + 0.5D, support.getY() + 1.0D, support.getZ() + 0.5D),
			Direction.UP,
			support,
			false
		);
		ActionResult result;
		try {
			result = client.interactionManager.interactBlock(player, hand, hitResult);
			if (result.isAccepted()) {
				player.swingHand(hand);
			}
		}
		finally {
			placementSneakController.release(client);
		}
		return new PlacementAttempt(result.isAccepted(), result.isAccepted() ? "accepted" : "interact_" + result);
	}

	private static Hand selectFurnacePlacementHand(MinecraftClient client, ClientPlayerEntity player) {
		if (player.getOffHandStack().isOf(Items.FURNACE)) {
			return Hand.OFF_HAND;
		}
		return selectHotbarItem(client, player, Items.FURNACE) ? Hand.MAIN_HAND : null;
	}

	private static boolean selectHotbarItem(MinecraftClient client, ClientPlayerEntity player, Item item) {
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findInventorySlot(handler, item);
		if (sourceSlot < 0) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			selectAndSyncHotbarSlot(client, player, sourceSlot - PlayerScreenHandler.HOTBAR_START);
			return true;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, selectedHotbarSlot, SlotActionType.SWAP, player);
		selectAndSyncHotbarSlot(client, player, selectedHotbarSlot);
		ItemStack selected = player.getInventory().getSelectedStack();
		return !selected.isEmpty() && selected.isOf(item);
	}

	private static void selectAndSyncHotbarSlot(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot) {
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
		}
	}

	private static int findInventorySlot(ScreenHandler handler, Item item) {
		if (!(handler instanceof PlayerScreenHandler)) {
			return -1;
		}
		ItemStack offhand = handler.getSlot(PlayerScreenHandler.OFFHAND_ID).getStack();
		if (!offhand.isEmpty() && offhand.isOf(item)) {
			return PlayerScreenHandler.OFFHAND_ID;
		}
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && stack.isOf(item)) {
				return slot;
			}
		}
		return -1;
	}

	private static Optional<BlockPos> chooseFurnacePlacement(MinecraftClient client, ClientPlayerEntity player) {
		BlockPos origin = player.getBlockPos();
		for (BlockPos candidate : SmeltingPlannerService.furnacePlacementCandidatePositions(origin)) {
			if (canPlaceAt(client, candidate)) {
				return Optional.of(candidate.toImmutable());
			}
		}
		return Optional.empty();
	}

	private static boolean canPlaceAt(MinecraftClient client, BlockPos pos) {
		if (client == null || client.world == null || !client.world.isChunkLoaded(pos) || !client.world.isChunkLoaded(pos.down())) {
			return false;
		}
		if (WorldPlacePreservation.contains(client.world, pos)) return false;
		BlockState target = client.world.getBlockState(pos);
		BlockState support = client.world.getBlockState(pos.down());
		return (target.isAir() || target.isReplaceable())
			&& support.isSideSolidFullSquare(client.world, pos.down(), Direction.UP)
			&& client.world.canPlace(Blocks.FURNACE.getDefaultState(), pos, ShapeContext.ofPlacement(client.player));
	}

	private static boolean withinInteractionRange(ClientPlayerEntity player, BlockPos pos) {
		return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= INTERACTION_RANGE_SQUARED;
	}

	private static BlockPos stationPos(SmeltingStationKey key) {
		if (key == null || key.dimensionId().contains("#open_screen")) {
			return null;
		}
		return new BlockPos(key.x(), key.y(), key.z());
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		placementSneakController.release(clientSupplier.get());
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		placementSneakController.release(clientSupplier.get());
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		if (request.type() == WorldTaskType.SMELT_ITEMS) {
			processManager.cancelProcessesForOption(smeltArgs(request).optionId());
		}
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private void closeOpenedStationIfSafe() {
		if (!openedStationForTask) {
			return;
		}
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player != null
			&& player.currentScreenHandler instanceof AbstractFurnaceScreenHandler
			&& player.currentScreenHandler.getCursorStack().isEmpty()) {
			ScreenCloseSafety.closeHandledScreen(player, "smelting_station_close");
		}
		openedStationForTask = false;
	}

	private void cancelNavigationIfStarted() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
	}

	private static boolean isSmeltingType(WorldTaskType type) {
		return type == WorldTaskType.SMELT_ITEMS || type == WorldTaskType.COLLECT_SMELTED_ITEMS;
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.type() != right.type()) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.task(), right.task());
	}

	private static SmeltItemsStepArgs smeltArgs(WorldTaskRequest request) {
		return ((WorldTaskRequest.SmeltItems) request.task()).args();
	}

	private static CollectSmeltedItemsStepArgs collectArgs(WorldTaskRequest request) {
		return ((WorldTaskRequest.CollectSmeltedItems) request.task()).args();
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "Smelting", event, null, null);
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? null : Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static SmeltingSlotSnapshot screenSlotSnapshot(AbstractFurnaceScreenHandler handler) {
		return new SmeltingSlotSnapshot(
			itemId(handler.getSlot(0).getStack()),
			handler.getSlot(0).getStack().getCount(),
			itemId(handler.getSlot(1).getStack()),
			handler.getSlot(1).getStack().getCount(),
			itemId(handler.getSlot(2).getStack()),
			handler.getSlot(2).getStack().getCount(),
			0,
			200,
			handler.isBurning()
		);
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

	private void reset() {
		placementSneakController.release(clientSupplier.get());
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		appliedTask = null;
		terminalEventEmitted = false;
		openedStationForTask = false;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private record FuelSelection(String itemId, int quantity) {
	}

	private record PlacementAttempt(boolean placed, String reason) {
	}

	private record StationReadiness(boolean ready, TaskFailure failure) {
		private static StationReadiness readyState() {
			return new StationReadiness(true, null);
		}

		private static StationReadiness notReady() {
			return new StationReadiness(false, null);
		}

		private static StationReadiness failed(TaskFailure failure) {
			return new StationReadiness(false, failure);
		}
	}
}
