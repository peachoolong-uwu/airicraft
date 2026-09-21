package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class BlockBreakTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;
	private static final int TARGET_TIMEOUT_TICKS = 200;
	private static final Direction BREAK_FACE = Direction.UP;

	private final Supplier<MinecraftClient> clientSupplier;
	private final CameraController cameraController;

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
	private int targetIndex;
	private int brokenTargets;
	private int skippedTargets;
	private boolean breakingActive;
	private long targetStartTick = -1L;

	public BlockBreakTaskExecutor(CameraController cameraController) {
		this(MinecraftClient::getInstance, cameraController);
	}

	BlockBreakTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this(clientSupplier, new CameraController());
	}

	private BlockBreakTaskExecutor(Supplier<MinecraftClient> clientSupplier, CameraController cameraController) {
		this.cameraController = Objects.requireNonNull(cameraController);
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.BREAK_BLOCKS) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (!BlockInteractionTaskExecutor.actuationAllowed(sessionSnapshot)) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.interactionManager == null || client.world == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "interaction_busy"));
		}

		BlockBreakStepArgs args = ((WorldTaskRequest.BreakBlocks) request.task()).args();
		while (targetIndex < args.targets().size()) {
			Optional<TaskTerminalEvent> event = tickTarget(sessionSnapshot, client, player, request, args.targets().get(targetIndex));
			if (event.isPresent() || breakingActive) {
				return event;
			}
		}
		return complete(request, "break_blocks_succeeded brokenTargets=" + brokenTargets + " skippedTargets=" + skippedTargets);
	}

	private Optional<TaskTerminalEvent> tickTarget(
		SessionSnapshot sessionSnapshot,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		BlockBreakStepArgs.Target target
	) {
		BlockPos pos = blockPos(target.position());
		if (!client.world.isChunkLoaded(pos)) {
			return fail(request, TaskFailure.of(TaskFailureCode.ENVIRONMENT_CHANGED, "target_unloaded targetPos=" + compactPos(pos)));
		}
		BlockState state = client.world.getBlockState(pos);
		if (satisfied(state)) {
			skippedTargets++;
			targetIndex++;
			breakingActive = false;
			targetStartTick = -1L;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_skipped targetPos=" + compactPos(pos));
			return Optional.empty();
		}
		String currentBlockId = blockId(state);
		if (!target.expectedBlockIds().contains(currentBlockId)) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_block_mismatch targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId));
		}
		if (!withinInteractionRange(player, Vec3d.ofCenter(pos))) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_out_of_range targetPos=" + compactPos(pos)));
		}
		cameraController.lookAtNow(client, Vec3d.ofCenter(pos));
		long tick = sessionSnapshot == null ? 0L : sessionSnapshot.tickCount();
		if (!breakingActive) {
			MiningToolPreparation.Result toolSelection =
				MiningToolPreparation.ensureSelected(client, player, List.of(state));
			if (!toolSelection.ok()) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_ITEM, toolSelection.message()));
			}
			boolean accepted = client.interactionManager.attackBlock(pos, BREAK_FACE);
			if (!accepted) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "break_start_failed targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId));
			}
			player.swingHand(Hand.MAIN_HAND);
			breakingActive = true;
			targetStartTick = tick;
		}
		if (tick - targetStartTick > TARGET_TIMEOUT_TICKS) {
			client.interactionManager.cancelBlockBreaking();
			return fail(request, TaskFailure.of(TaskFailureCode.TRANSIENT, "break_timeout targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId));
		}
		client.interactionManager.updateBlockBreakingProgress(pos, BREAK_FACE);
		player.swingHand(Hand.MAIN_HAND);
		BlockState after = client.world.isChunkLoaded(pos) ? client.world.getBlockState(pos) : state;
		if (satisfied(after)) {
			brokenTargets++;
			targetIndex++;
			breakingActive = false;
			targetStartTick = -1L;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_broken targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId);
			return Optional.empty();
		}
		String afterBlockId = blockId(after);
		if (!target.expectedBlockIds().contains(afterBlockId)) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_block_mismatch targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId + " afterBlockId=" + afterBlockId));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "breaking targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private static boolean satisfied(BlockState state) {
		return state.isAir() || state.getBlock() instanceof FluidBlock;
	}

	private static boolean withinInteractionRange(ClientPlayerEntity player, Vec3d pos) {
		return player.squaredDistanceTo(pos) <= INTERACTION_RANGE_SQUARED;
	}

	private static BlockPos blockPos(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}

	private static String blockId(BlockState state) {
		return Registries.BLOCK.getId(state.getBlock()).toString();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.sourceJobId(), right.sourceJobId())
			&& left.type() == right.type();
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "BlockBreak", event, null, null);
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
		if (breakingActive) {
			MinecraftClient client = clientSupplier.get();
			if (client != null && client.interactionManager != null) {
				client.interactionManager.cancelBlockBreaking();
			}
		}
		appliedTask = null;
		terminalEventEmitted = false;
		snapshot = TaskExecutionSnapshot.idle();
		targetIndex = 0;
		brokenTargets = 0;
		skippedTargets = 0;
		breakingActive = false;
		targetStartTick = -1L;
	}
}
